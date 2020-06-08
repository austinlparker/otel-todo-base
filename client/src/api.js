import axios from 'axios'  

/* eslint-disable no-undef */
const SERVER_URL = config.VUE_APP_ENV_Server || process.env.VUE_APP_ENV_Server;
/* eslint-enable no-undef */

const instance = axios.create({  
  baseURL: SERVER_URL,  
  timeout: 30000  
});  
  
export default {  
  // (C)reate  
  createNew: (text, completed) => instance.post('todos', {title: text, completed: completed}),  
  // (R)ead  
  getAll: () => instance.get('todos', {  
    transformResponse: [function (data) {  
      return data? JSON.parse(data)._embedded.todos : data;  
    }]  
  }),  
  // (U)pdate  
  updateForId: (id, text, completed) => instance.put('todos/'+id, {title: text, completed: completed}),  
  // (D)elete  
  removeForId: (id) => instance.delete('todos/'+id),

  getFact: () => instance.get('fact', { transformResponse: [function (data) {
    return JSON.parse(data);
  }]})
}